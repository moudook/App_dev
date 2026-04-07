package com.example.smarty.core.common.util

import android.content.Context
import com.example.smarty.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Centralized date/time formatting utilities.
 *
 * Previously inlined as private functions inside:
 * - ChatMessageItem.formatTimestamp()
 *
 * All callers should import from this single source.
 */
object DateUtils {
    /**
     * Formats a timestamp into a relative or absolute human-readable string.
     *
     * - Under 1 min → "Just now"
     * - Under 1 hour → "X min ago"
     * - Under 1 day → "X hours ago"
     * - Older → "Mar 3, 8:45 pm"
     *
     * @param timestamp Unix timestamp in milliseconds.
     * @param context   Context for resolving string resources.
     */
    fun formatRelativeTimestamp(
        timestamp: Long,
        context: Context,
    ): String {
        val now = System.currentTimeMillis()
        val diff = now - timestamp

        return when {
            diff < 60_000 -> context.getString(R.string.just_now)
            diff < 3_600_000 -> context.getString(R.string.minutes_ago, diff / 60_000)
            diff < 86_400_000 -> context.getString(R.string.hours_ago, diff / 3_600_000)
            else -> {
                val sdf = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
                sdf.format(Date(timestamp)).lowercase()
            }
        }
    }

    /**
     * Formats a timestamp into an absolute date string (e.g., "Mar 3, 2026").
     */
    fun formatDate(
        timestamp: Long,
        pattern: String = "MMM d, yyyy",
    ): String {
        val sdf = SimpleDateFormat(pattern, Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    /**
     * Formats a timestamp into a date-time string (e.g., "Mar 3, 8:45 PM").
     */
    fun formatDateTime(
        timestamp: Long,
        pattern: String = "MMM d, h:mm a",
    ): String {
        val sdf = SimpleDateFormat(pattern, Locale.getDefault())
        return sdf.format(Date(timestamp))
    }
}
