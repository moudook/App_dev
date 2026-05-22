package com.example.smarty.server.utils

import org.slf4j.LoggerFactory

/**
 * Input Validation Utility.
 *
 * Single Responsibility: Only handles input validation and sanitization.
 * Security: Prevents injection attacks, XSS, and other input-based vulnerabilities.
 *
 * Usage:
 * ```
 * InputValidation.validateQuery(query)
 * InputValidation.validateUserId(userId)
 * InputValidation.sanitizeInput(input)
 * ```
 */
object InputValidation {
    private val logger = LoggerFactory.getLogger(InputValidation::class.java)

    // Maximum lengths
    private const val MAX_QUERY_LENGTH = 10000
    private const val MAX_USER_ID_LENGTH = 64
    private const val MAX_TITLE_LENGTH = 500
    private const val MAX_CONTENT_LENGTH = 100000

    // Dangerous patterns that could indicate injection attacks
    private val injectionPatterns =
        listOf(
            Regex("<script", RegexOption.IGNORE_CASE),
            Regex("javascript:", RegexOption.IGNORE_CASE),
            Regex("onerror\\s*=", RegexOption.IGNORE_CASE),
            Regex("onload\\s*=", RegexOption.IGNORE_CASE),
            Regex("onclick\\s*=", RegexOption.IGNORE_CASE),
            Regex("eval\\s*\\(", RegexOption.IGNORE_CASE),
            Regex("alert\\s*\\(", RegexOption.IGNORE_CASE),
            // SQL comment
            Regex("--\\s*$"),
            Regex(";\\s*DROP\\s+", RegexOption.IGNORE_CASE),
            Regex(";\\s*DELETE\\s+", RegexOption.IGNORE_CASE),
            Regex(";\\s*UPDATE\\s+", RegexOption.IGNORE_CASE),
            Regex(";\\s*INSERT\\s+", RegexOption.IGNORE_CASE),
            Regex("\\bOR\\s+1\\s*=\\s*1\\b", RegexOption.IGNORE_CASE),
            Regex("\\bAND\\s+1\\s*=\\s*1\\b", RegexOption.IGNORE_CASE),
            Regex("UNION\\s+SELECT", RegexOption.IGNORE_CASE),
            // Path traversal
            Regex("\\.\\./", RegexOption.IGNORE_CASE),
            // Path traversal (Windows)
            Regex("\\.\\.\\\\", RegexOption.IGNORE_CASE),
        )

    /**
     * Validate a user query.
     * @throws IllegalArgumentException if query is invalid
     */
    fun validateQuery(query: String?) {
        require(!query.isNullOrBlank()) { "Query cannot be empty" }
        require(query.length <= MAX_QUERY_LENGTH) {
            "Query too long (max ${MAX_QUERY_LENGTH} characters)"
        }

        // Check for injection patterns
        injectionPatterns.forEach { pattern ->
            require(!pattern.containsMatchIn(query)) {
                "Invalid characters in query"
            }
        }

        logger.debug("Query validation passed (length: ${query.length})")
    }

    /**
     * Validate a user ID.
     * @throws IllegalArgumentException if user ID is invalid
     */
    fun validateUserId(userId: String?) {
        require(!userId.isNullOrBlank()) { "User ID cannot be empty" }
        require(userId.length <= MAX_USER_ID_LENGTH) {
            "User ID too long (max ${MAX_USER_ID_LENGTH} characters)"
        }
        require(userId.matches(Regex("^[a-zA-Z0-9_-]{1,${MAX_USER_ID_LENGTH}}$"))) {
            "Invalid user ID format (only alphanumeric, underscore, and hyphen allowed)"
        }

        logger.debug("User ID validation passed: ${userId.take(8)}...")
    }

    /**
     * Validate a title.
     * @throws IllegalArgumentException if title is invalid
     */
    fun validateTitle(title: String?) {
        require(!title.isNullOrBlank()) { "Title cannot be empty" }
        require(title.length <= MAX_TITLE_LENGTH) {
            "Title too long (max ${MAX_TITLE_LENGTH} characters)"
        }

        // Check for injection patterns
        injectionPatterns.forEach { pattern ->
            require(!pattern.containsMatchIn(title)) {
                "Invalid characters in title"
            }
        }

        logger.debug("Title validation passed (length: ${title.length})")
    }

    /**
     * Validate content (notes, messages, etc.).
     * @throws IllegalArgumentException if content is invalid
     */
    fun validateContent(content: String?) {
        require(!content.isNullOrBlank()) { "Content cannot be empty" }
        require(content.length <= MAX_CONTENT_LENGTH) {
            "Content too long (max ${MAX_CONTENT_LENGTH} characters)"
        }

        // Check for injection patterns
        injectionPatterns.forEach { pattern ->
            require(!pattern.containsMatchIn(content)) {
                "Invalid characters in content"
            }
        }

        logger.debug("Content validation passed (length: ${content.length})")
    }

    /**
     * Sanitize input by removing potentially dangerous characters.
     * Use this when you want to clean input rather than reject it.
     */
    fun sanitizeInput(input: String): String {
        var sanitized = input.trim()

        // Remove null bytes
        sanitized = sanitized.replace("\u0000", "")

        // Remove control characters except newline and tab
        sanitized = sanitized.replace(Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]"), "")

        // Encode HTML entities
        sanitized =
            sanitized
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#x27;")

        logger.debug("Input sanitized (length: ${input.length} -> ${sanitized.length})")
        return sanitized
    }

    /**
     * Validate an ID (for notes, sessions, events, etc.).
     * @throws IllegalArgumentException if ID is invalid
     */
    fun validateId(
        id: String?,
        entityType: String = "ID",
    ) {
        require(!id.isNullOrBlank()) { "$entityType cannot be empty" }
        require(id.length <= 64) {
            "$entityType too long (max 64 characters)"
        }
        require(id.matches(Regex("^[a-zA-Z0-9_-]{1,64}$"))) {
            "Invalid $entityType format"
        }

        logger.debug("$entityType validation passed: ${id.take(8)}...")
    }

    /**
     * Validate email format (basic check).
     * @throws IllegalArgumentException if email is invalid
     */
    fun validateEmail(email: String?) {
        if (email.isNullOrBlank()) {
            return // Email is often optional
        }

        require(email.length <= 254) { "Email too long" }
        require(email.matches(Regex("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$"))) {
            "Invalid email format"
        }

        logger.debug("Email validation passed")
    }

    /**
     * Validate a session ID.
     * @throws IllegalArgumentException if session ID is invalid
     */
    fun validateSessionId(sessionId: String?) {
        validateId(sessionId, "Session ID")
    }

    /**
     * Validate a note ID.
     * @throws IllegalArgumentException if note ID is invalid
     */
    fun validateNoteId(noteId: String?) {
        validateId(noteId, "Note ID")
    }

    /**
     * Validate a calendar event ID.
     * @throws IllegalArgumentException if event ID is invalid
     */
    fun validateEventId(eventId: String?) {
        validateId(eventId, "Event ID")
    }
}
