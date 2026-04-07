package com.example.smarty.core.common.util

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Screen size utility
 * Helps create responsive layouts
 */
object ScreenSizeUtil {
    enum class ScreenSize {
        Compact,
        Medium,
        Expanded,
    }

    @Composable
    fun getScreenSize(): ScreenSize {
        val widthDp = LocalConfiguration.current.screenWidthDp
        return when {
            widthDp < 600 -> ScreenSize.Compact
            widthDp < 840 -> ScreenSize.Medium
            else -> ScreenSize.Expanded
        }
    }

    @Composable
    fun isCompact(): Boolean = getScreenSize() == ScreenSize.Compact

    @Composable
    fun isMedium(): Boolean = getScreenSize() == ScreenSize.Medium

    @Composable
    fun isExpanded(): Boolean = getScreenSize() == ScreenSize.Expanded
}

/**
 * DP to PX conversion utility
 */
object DpUtils {
    fun dpToPx(
        context: Context,
        dp: Dp,
    ): Int {
        val density = context.resources.displayMetrics.density
        return (dp.value * density).toInt()
    }

    fun pxToDp(
        context: Context,
        px: Int,
    ): Dp {
        val density = context.resources.displayMetrics.density
        return (px / density).dp
    }
}

/**
 * Time formatting utility
 * Formats timestamps for display
 */
object TimeFormatUtil {
    fun formatRelativeTime(timestamp: Long): String {
        val now = System.currentTimeMillis()
        val diff = now - timestamp

        return when {
            diff < 60000 -> "Just now"
            diff < 3600000 -> "${diff / 60000}m ago"
            diff < 86400000 -> "${diff / 3600000}h ago"
            diff < 604800000 -> "${diff / 86400000}d ago"
            else -> android.text.format.DateFormat.format("MMM dd, yyyy", timestamp).toString()
        }
    }

    fun formatTime(timestamp: Long): String {
        return android.text.format.DateFormat.format("hh:mm a", timestamp).toString()
    }

    fun formatDate(timestamp: Long): String {
        return android.text.format.DateFormat.format("MMM dd, yyyy", timestamp).toString()
    }

    fun formatDateTime(timestamp: Long): String {
        return "${formatDate(timestamp)} ${formatTime(timestamp)}"
    }
}

/**
 * String manipulation utility
 */
object StringUtils {
    fun truncate(
        text: String,
        maxLength: Int,
        suffix: String = "...",
    ): String {
        return if (text.length > maxLength) {
            text.take(maxLength - suffix.length) + suffix
        } else {
            text
        }
    }

    fun capitalizeFirst(text: String): String {
        return text.replaceFirstChar { it.uppercase() }
    }

    fun removeHtmlTags(html: String): String {
        return html.replace(Regex("<[^>]*>"), "")
    }

    fun isValidUrl(url: String): Boolean {
        return android.util.Patterns.WEB_URL.matcher(url).matches()
    }

    fun isValidEmail(email: String): Boolean {
        return android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()
    }

    fun isValidPhone(phone: String): Boolean {
        return android.util.Patterns.PHONE.matcher(phone).matches()
    }
}

/**
 * File size formatting utility
 */
object FileSizeUtil {
    fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1048576 -> "${bytes / 1024} KB"
            bytes < 1073741824 -> "${bytes / 1048576} MB"
            else -> "${bytes / 1073741824} GB"
        }
    }
}

/**
 * Color utility functions
 */
object ColorUtils {
    fun darkenColor(
        color: androidx.compose.ui.graphics.Color,
        factor: Float = 0.8f,
    ): androidx.compose.ui.graphics.Color {
        return androidx.compose.ui.graphics.Color(
            red = color.red * factor,
            green = color.green * factor,
            blue = color.blue * factor,
            alpha = color.alpha,
        )
    }

    fun lightenColor(
        color: androidx.compose.ui.graphics.Color,
        factor: Float = 0.8f,
    ): androidx.compose.ui.graphics.Color {
        return androidx.compose.ui.graphics.Color(
            red = color.red + (1 - color.red) * factor,
            green = color.green + (1 - color.green) * factor,
            blue = color.blue + (1 - color.blue) * factor,
            alpha = color.alpha,
        )
    }

    fun withAlpha(
        color: androidx.compose.ui.graphics.Color,
        alpha: Float,
    ): androidx.compose.ui.graphics.Color {
        return color.copy(alpha = alpha)
    }
}

/**
 * Collection utility functions
 */
object CollectionUtils {
    fun <T> List<T>.safeGet(
        index: Int,
        default: T,
    ): T {
        return if (index in indices) this[index] else default
    }

    fun <T> List<T>.chunkedSafe(size: Int): List<List<T>> {
        return if (isEmpty()) emptyList() else chunked(size)
    }

    fun <T, R> List<T>.mapNotNullTransform(transform: (T) -> R?): List<R> {
        return mapNotNull { transform(it) }
    }
}

/**
 * Boolean utility functions
 */
object BooleanUtils {
    fun toggle(current: Boolean): Boolean = !current

    fun allTrue(vararg values: Boolean): Boolean = values.all { it }

    fun anyTrue(vararg values: Boolean): Boolean = values.any { it }

    fun noneTrue(vararg values: Boolean): Boolean = values.none { it }
}
