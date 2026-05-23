package com.example.smarty.server.services

import com.example.smarty.server.llm.LlmMessage
import com.example.smarty.server.llm.LlmProvider
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.util.regex.Pattern

/**
 * Utility Service for common tasks like Date Parsing, Summarization, Categorization.
 * Migrated from Android App to Server.
 * All LLM calls route through OpenCode CLI free models.
 */
class UtilityService(
    private val llmProvider: LlmProvider,
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
            val now =
                java.time.LocalDateTime.now(
                    java.time.ZoneId.of(userTimezone).rules.getOffset(java.time.Instant.now()).let {
                        java.time.ZoneId.ofOffset("UTC", java.time.ZoneOffset.ofTotalSeconds(it.totalSeconds))
                    },
                )

            return when {
                unit.startsWith("min") -> now.plusMinutes(amount).toString()
                unit.startsWith("hour") || unit.startsWith("hr") -> now.plusHours(amount).toString()
                unit.startsWith("day") -> now.plusDays(amount).toString()
                else -> null
            }
        }

        // 2. LLM Fallback (Smart) — uses OpenCode CLI free model
        try {
            logger.info("[UtilityService] LLM date extraction requested — query: '{}', timezone: {}", query.take(80), userTimezone)
            val llmStart = System.currentTimeMillis()
            val response =
                llmProvider.generate(
                    messages =
                        listOf(
                            LlmMessage(
                                role = LlmMessage.Role.USER,
                                content =
                                    "Extract the intended date and time from this text: '$query'. " +
                                        "Return ONLY the ISO-8601 string (e.g., 2023-10-25T14:30:00). " +
                                        "User is in $userTimezone. If no date, return 'null'.",
                            ),
                        ),
                )
            val llmDuration = System.currentTimeMillis() - llmStart
            val result = response.content?.trim()
            logger.info("[UtilityService] LLM date extraction completed in {}ms — result: '{}'", llmDuration, result)
            return if (result == "null") null else result
        } catch (e: Exception) {
            logger.error("[UtilityService] LLM date extraction failed: {}", e.message, e)
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
