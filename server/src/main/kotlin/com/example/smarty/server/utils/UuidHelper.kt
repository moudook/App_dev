package com.example.smarty.server.utils

import java.util.UUID

/**
 * Convert string to UUID with descriptive error.
 *
 * Usage:
 * ```
 * val uuid = userId.toUuid()
 * ```
 */
fun String.toUuid(): UUID =
    try {
        UUID.fromString(this)
    } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("Invalid UUID format: '$this'", e)
    }

/**
 * Convert nullable string to nullable UUID.
 */
fun String?.toUuidOrNull(): UUID? = this?.toUuid()

/**
 * Convert list of strings to list of UUIDs.
 */
fun List<String>.toUuidList(): List<UUID> = map { it.toUuid() }

/**
 * Convert list of nullable strings to list of nullable UUIDs.
 */
fun List<String?>.toUuidOrNullList(): List<UUID?> = map { it?.toUuidOrNull() }

/**
 * Convert UUID to string safely.
 */
fun UUID?.toStringOrNull(): String? = this?.toString()

/**
 * Convert list of UUIDs to list of strings.
 */
fun List<UUID>.toStringList(): List<String> = map { it.toString() }

/**
 * Validate UUID format without throwing exception.
 * @return true if valid UUID format, false otherwise
 */
fun String.isValidUuid(): Boolean =
    try {
        UUID.fromString(this)
        true
    } catch (e: IllegalArgumentException) {
        false
    }

/**
 * Generate a random UUID string.
 */
fun randomUuidString(): String = UUID.randomUUID().toString()

/**
 * UUID validation result for form validation.
 */
data class UuidValidation(
    val isValid: Boolean,
    val uuid: UUID? = null,
    val error: String? = null,
) {
    companion object {
        fun valid(uuid: UUID): UuidValidation = UuidValidation(isValid = true, uuid = uuid)

        fun invalid(error: String): UuidValidation = UuidValidation(isValid = false, error = error)
    }
}

/**
 * Validate and parse a UUID string.
 * @return UuidValidation with parsed UUID or error
 */
fun String.validateUuid(): UuidValidation =
    try {
        UuidValidation.valid(UUID.fromString(this))
    } catch (e: IllegalArgumentException) {
        UuidValidation.invalid("Invalid UUID format: '$this'")
    }
