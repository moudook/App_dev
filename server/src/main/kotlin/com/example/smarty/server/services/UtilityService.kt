package com.example.smarty.server.services

import com.example.smarty.server.llm.LlmMessage
import com.example.smarty.server.llm.LlmProvider
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.util.regex.Pattern

/**
 * Utility Service for common tasks like Date Parsing, Summarization, Categorization.
 * Migrated from Android App to Server.
 */
class UtilityService(
    private val llmProvider: LlmProvider, // Typically gemini-3-flash
) {
    private val logger = LoggerFactory.getLogger(UtilityService::class.java)

    /**
     * Extract date/time from natural language query.
     */
    suspend fun extractDateTime(
        query: String,
        userTimezone: String = "UTC",
    ): String? {
        // 1. Try Regex (Fast)
        val relativePattern = Pattern.compile("(\\d+)\\s*(hour|minute|min|hr|day)s?", Pattern.CASE_INSENSITIVE)
        val matcher = relativePattern.matcher(query)

        if (matcher.find()) {
            val amount = matcher.group(1).toLong()
            val unit = matcher.group(2).lowercase()
            // Use user's timezone for proper date/time calculation
            val now =
                java.time.LocalDateTime.now(
                    java.time.ZoneId.of(userTimezone).rules.getOffset(java.time.Instant.now()).let {
                        java.time.ZoneId.ofOffset("UTC", java.time.ZoneOffset.ofTotalSeconds(it.totalSeconds))
                    },
                )

            // Simplified relative logic
            return when {
                unit.startsWith("min") -> now.plusMinutes(amount).toString()
                unit.startsWith("hour") || unit.startsWith("hr") -> now.plusHours(amount).toString()
                unit.startsWith("day") -> now.plusDays(amount).toString()
                else -> null
            }
        }

        // 2. LLM Fallback (Smart)
        try {
            val response =
                llmProvider.generate(
                    messages =
                        listOf(
                            LlmMessage(
                                role = LlmMessage.Role.USER,
                                content = "Extract the intended date and time from this text: '$query'. Return ONLY the ISO-8601 string (e.g., 2023-10-25T14:30:00). User is in $userTimezone. If no date, return 'null'.",
                            ),
                        ),
                    model = "gemini-3-flash",
                )
            val result = response.content?.trim()
            return if (result == "null") null else result
        } catch (e: Exception) {
            logger.error("LLM date extraction failed", e)
            return null
        }
    }

    /**
     * Categorize a note or task.
     */
    fun categorize(content: String): String {
        val lower = content.lowercase()
        return when {
            lower.contains("buy") || lower.contains("get") || lower.contains("shop") -> "Shopping"
            lower.contains("meeting") || lower.contains("call") || lower.contains("appointment") -> "Work"
            lower.contains("watch") || lower.contains("read") || lower.contains("listen") -> "Personal"
            lower.contains("idea") || lower.contains("think") -> "Ideas"
            else -> "General"
        }
    }
}
