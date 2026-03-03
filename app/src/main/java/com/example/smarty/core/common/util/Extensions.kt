package com.example.smarty.core.common.util

import android.content.Context

/**
 * Centralized extension functions used across the Smarty codebase.
 *
 * This file collects common String, Collection, and other utility extensions
 * that were previously duplicated or inlined in multiple locations.
 *
 * Guidelines:
 * - Only add extensions that are (or will be) used in 2+ locations
 * - Keep each extension well-documented
 * - Prefer pure functions (no side effects)
 */

// ═══════════════════════════════════════════════════════════════════
// STRING EXTENSIONS
// ═══════════════════════════════════════════════════════════════════

/**
 * Truncates a string to [maxLength] characters, appending [suffix] if truncated.
 *
 * Example:
 *   "Hello World".truncate(5) → "Hello…"
 *   "Hi".truncate(5)          → "Hi"
 */
fun String.truncate(maxLength: Int, suffix: String = "…"): String {
    return if (length <= maxLength) this
    else take(maxLength) + suffix
}

/**
 * Returns this string if not blank, or [default] otherwise.
 * Useful for safe fallback chains.
 *
 * Example:
 *   "".orDefault("Untitled") → "Untitled"
 *   "My Note".orDefault("Untitled") → "My Note"
 */
fun String?.orDefault(default: String): String {
    return if (this.isNullOrBlank()) default else this
}

/**
 * Capitalizes the first letter of each word.
 *
 * Example:
 *   "hello world".capitalizeWords() → "Hello World"
 */
fun String.capitalizeWords(): String {
    return split(" ").joinToString(" ") { word ->
        word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }
}

// ═══════════════════════════════════════════════════════════════════
// COLLECTION EXTENSIONS
// ═══════════════════════════════════════════════════════════════════

/**
 * Returns a human-readable count string.
 *
 * Example:
 *   5.countLabel("note", "notes") → "5 notes"
 *   1.countLabel("note", "notes") → "1 note"
 *   0.countLabel("note", "notes") → "0 notes"
 */
fun Int.countLabel(singular: String, plural: String): String {
    return "$this ${if (this == 1) singular else plural}"
}

// ═══════════════════════════════════════════════════════════════════
// FILE SIZE FORMATTING
// ═══════════════════════════════════════════════════════════════════

/**
 * Formats a byte count into a human-readable file size string.
 *
 * Example:
 *   1024L.formatFileSize()      → "1.0 KB"
 *   1048576L.formatFileSize()   → "1.0 MB"
 *   500L.formatFileSize()       → "500 B"
 */
fun Long.formatFileSize(): String {
    return when {
        this < 1024 -> "$this B"
        this < 1024 * 1024 -> "%.1f KB".format(this / 1024.0)
        this < 1024 * 1024 * 1024 -> "%.1f MB".format(this / (1024.0 * 1024.0))
        else -> "%.2f GB".format(this / (1024.0 * 1024.0 * 1024.0))
    }
}
