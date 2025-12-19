package com.example.smarty.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * =============================================================================
 * AI MEMORY
 * =============================================================================
 *
 * Persistent AI memory storage - provider-agnostic.
 * Stores user preferences, patterns, and learned behaviors.
 *
 * PRIVACY RULE: NEVER store content from private notes.
 * Only store abstract patterns and preferences.
 *
 * Examples of GOOD memory entries:
 * - "User prefers concise responses"
 * - "User often asks about work notes on Mondays"
 * - "User uses informal language"
 * - "User's timezone is EST"
 *
 * Examples of BAD memory entries (NEVER store):
 * - "User's password is..." (security violation)
 * - "User discussed Project Alpha" (private note content)
 * - "User's meeting with John at 3pm" (private calendar content)
 *
 * =============================================================================
 */
@Entity(
    tableName = "ai_memories",
    indices = [
        Index(value = ["type"]),
        Index(value = ["lastUsedAt"]),
        Index(value = ["confidence"])
    ]
)
data class AIMemory(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),

    /**
     * Type of memory - helps with categorization and retrieval
     */
    val type: MemoryType,

    /**
     * Abstract description of the learned pattern/preference.
     * NEVER contains raw private content.
     */
    val content: String,

    /**
     * How confident AI is in this memory (0.0 to 1.0).
     * Higher values = more reliable pattern.
     * Starts at 1.0, may decrease if user contradicts it.
     */
    val confidence: Float = 1.0f,

    /**
     * What triggered this learning (optional).
     * Abstract description only, never private content.
     * e.g., "User explicitly stated preference" or "Observed pattern over 5 interactions"
     */
    val source: String? = null,

    /**
     * When this memory was created
     */
    val createdAt: Long = System.currentTimeMillis(),

    /**
     * When this memory was last used in context building
     */
    val lastUsedAt: Long = System.currentTimeMillis(),

    /**
     * How many times this memory has been used.
     * Helps identify frequently useful memories.
     */
    val usageCount: Int = 1
)

/**
 * Types of AI memories.
 * Used for categorization and prioritization.
 */
enum class MemoryType {
    /**
     * User preferences for AI behavior.
     * Examples:
     * - "User prefers concise responses"
     * - "User likes bullet points"
     * - "User prefers formal language"
     */
    PREFERENCE,

    /**
     * Behavioral patterns observed over time.
     * Examples:
     * - "User often asks about work notes on Mondays"
     * - "User typically creates notes in the evening"
     * - "User reviews todos every morning"
     */
    PATTERN,

    /**
     * Communication style preferences.
     * Examples:
     * - "User uses informal language"
     * - "User appreciates humor"
     * - "User prefers direct answers"
     */
    STYLE,

    /**
     * General facts about user (non-private).
     * Examples:
     * - "User's project is called X" (if user shared publicly)
     * - "User's timezone is EST"
     * - "User is a software developer"
     */
    FACT
}
