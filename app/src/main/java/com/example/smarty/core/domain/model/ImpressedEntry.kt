package com.example.smarty.core.domain.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * =============================================================================
 * IMPRESSED LOG ENTRY
 * =============================================================================
 *
 * Secure log of AI actions that positively impacted the user.
 * Used for self-improvement and learning. Contains ONLY abstractions,
 * never raw content.
 *
 * GOOD entries (abstract, non-private):
 * - "Proactive summary suggestion was accepted"
 * - "User thanked AI after organizing 5 todos"
 * - "Search result was immediately helpful"
 * - "User completed action without modification"
 *
 * BAD entries (NEVER store - contains private info):
 * - "User liked note about Project Alpha"
 * - "Found meeting with John at 3pm"
 * - "Helped with password reset"
 * - Any specific note/event content
 *
 * Detection signals for "impressed":
 * - User says "thanks", "great", "perfect", "exactly", etc.
 * - User accepts a suggestion without modification
 * - User re-uses an AI-created note/category
 * - User completes a recommended action
 * - User explicitly expresses satisfaction
 *
 * =============================================================================
 */
@Entity(
    tableName = "impressed_log",
    indices = [
        Index(value = ["actionType"]),
        Index(value = ["timestamp"]),
        Index(value = ["userSignal"]),
    ],
)
data class ImpressedEntry(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    /**
     * What type of action was taken by the AI.
     * Abstract category, not specific content.
     * Examples:
     * - "NOTE_CREATION"
     * - "TODO_ORGANIZATION"
     * - "SEARCH_RESULT"
     * - "PROACTIVE_SUGGESTION"
     * - "SUMMARY_GENERATION"
     * - "CATEGORY_ASSIGNMENT"
     */
    val actionType: String,
    /**
     * Abstract context without private data.
     * Describes the situation, not the content.
     * Examples:
     * - "Created note from voice input"
     * - "Organized 5 overdue todos"
     * - "Found relevant notes for user query"
     * - "Suggested follow-up action"
     */
    val abstractContext: String,
    /**
     * How we detected user was impressed.
     * The signal that triggered this log entry.
     * Examples:
     * - "EXPLICIT_THANKS" - User said thanks/great/etc.
     * - "ACCEPTED_SUGGESTION" - User accepted without changes
     * - "REUSED_CREATION" - User reused AI-created content
     * - "IMMEDIATE_ACTION" - User acted on suggestion immediately
     * - "POSITIVE_FEEDBACK" - Explicit positive statement
     */
    val userSignal: String,
    /**
     * When this entry was logged.
     */
    val timestamp: Long = System.currentTimeMillis(),
)

/**
 * Constants for action types and user signals.
 * Provides consistency in logging.
 */
object ImpressedLogConstants {
    // Action Types
    const val ACTION_NOTE_CREATION = "NOTE_CREATION"
    const val ACTION_NOTE_UPDATE = "NOTE_UPDATE"
    const val ACTION_TODO_MANAGEMENT = "TODO_MANAGEMENT"
    const val ACTION_SEARCH_RESULT = "SEARCH_RESULT"
    const val ACTION_PROACTIVE_SUGGESTION = "PROACTIVE_SUGGESTION"
    const val ACTION_SUMMARY_GENERATION = "SUMMARY_GENERATION"
    const val ACTION_CATEGORY_ASSIGNMENT = "CATEGORY_ASSIGNMENT"
    const val ACTION_QUESTION_ANSWERED = "QUESTION_ANSWERED"
    const val ACTION_BATCH_OPERATION = "BATCH_OPERATION"
    const val ACTION_AUDIO_PLAYBACK = "AUDIO_PLAYBACK"
    const val ACTION_WEB_SEARCH = "WEB_SEARCH"

    // User Signals
    const val SIGNAL_EXPLICIT_THANKS = "EXPLICIT_THANKS"
    const val SIGNAL_ACCEPTED_SUGGESTION = "ACCEPTED_SUGGESTION"
    const val SIGNAL_REUSED_CREATION = "REUSED_CREATION"
    const val SIGNAL_IMMEDIATE_ACTION = "IMMEDIATE_ACTION"
    const val SIGNAL_POSITIVE_FEEDBACK = "POSITIVE_FEEDBACK"
    const val SIGNAL_NO_MODIFICATION = "NO_MODIFICATION"
    const val SIGNAL_REPEATED_REQUEST = "REPEATED_REQUEST"

    /**
     * Keywords that indicate user satisfaction.
     * Used for detecting EXPLICIT_THANKS signal.
     */
    val POSITIVE_KEYWORDS =
        listOf(
            "thanks", "thank you", "thx",
            "great", "awesome", "amazing", "wonderful",
            "perfect", "exactly", "that's it",
            "nice", "good", "excellent",
            "helpful", "love it", "brilliant",
        )

    /**
     * Check if a message contains positive feedback.
     */
    fun containsPositiveFeedback(message: String): Boolean {
        val lowerMessage = message.lowercase()
        return POSITIVE_KEYWORDS.any { keyword ->
            lowerMessage.contains(keyword)
        }
    }
}
