package com.example.smarty.data.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Global Error State Management.
 *
 * Single Responsibility: Centralized error tracking and dismissal.
 * DRY: Replaces individual error StateFlows in multiple ViewModels.
 * Global State: Shared across all features.
 *
 * Usage:
 * ```
 * // Report an error
 * globalErrorState.reportError(
 *     AppError(
 *         message = "Failed to save note",
 *         severity = ErrorSeverity.HIGH,
 *         feature = Feature.NOTES
 *     )
 * )
 *
 * // Dismiss an error
 * globalErrorState.dismissError(errorId)
 *
 * // Observe errors
 * val errors by globalErrorState.errors.collectAsState()
 * ```
 */
class GlobalErrorState {
    private val _errors = MutableStateFlow<List<AppError>>(emptyList())
    val errors: StateFlow<List<AppError>> = _errors.asStateFlow()

    /**
     * Report a new error.
     * @param error The error to report
     * @return The generated error ID for later dismissal
     */
    fun reportError(error: AppError): String {
        val errorWithId = error.copy(id = UUID.randomUUID().toString())
        _errors.value = _errors.value + errorWithId
        return errorWithId.id
    }

    /**
     * Report a simple error with default severity.
     */
    fun reportError(
        message: String,
        feature: Feature = Feature.GENERAL,
    ): String {
        return reportError(AppError(message = message, severity = ErrorSeverity.MEDIUM, feature = feature))
    }

    /**
     * Dismiss an error by ID.
     */
    fun dismissError(errorId: String) {
        _errors.value = _errors.value.filter { it.id != errorId }
    }

    /**
     * Clear all errors.
     */
    fun clearAllErrors() {
        _errors.value = emptyList()
    }

    /**
     * Clear errors for a specific feature.
     */
    fun clearErrorsForFeature(feature: Feature) {
        _errors.value = _errors.value.filter { it.feature != feature }
    }

    /**
     * Get the latest error.
     */
    val latestError: AppError?
        get() = _errors.value.lastOrNull()

    /**
     * Check if there are any errors.
     */
    val hasErrors: Boolean
        get() = _errors.value.isNotEmpty()

    /**
     * Get error count.
     */
    val errorCount: Int
        get() = _errors.value.size
}

/**
 * Represents an application error.
 *
 * @param id Unique identifier for dismissal
 * @param message User-friendly error message
 * @param severity Error severity level
 * @param feature Feature where error occurred
 * @param throwable Optional underlying exception
 * @param timestamp When the error occurred
 */
data class AppError(
    val id: String = "",
    val message: String,
    val severity: ErrorSeverity,
    val feature: Feature,
    val throwable: Throwable? = null,
    val timestamp: Long = System.currentTimeMillis(),
)

/**
 * Error severity levels.
 */
enum class ErrorSeverity {
    LOW, // Non-blocking, informational
    MEDIUM, // User should be aware
    HIGH, // Critical, requires attention
    CRITICAL, // App may be unstable
}

/**
 * App features for error categorization.
 */
enum class Feature {
    GENERAL,
    NOTES,
    CHAT,
    CALENDAR,
    AUDIO,
    SEARCH,
    SETTINGS,
    AUTH,
    SYNC,
    BACKUP,
}
