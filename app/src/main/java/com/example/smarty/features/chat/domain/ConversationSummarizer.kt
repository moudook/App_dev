package com.example.smarty.features.chat.domain

import android.util.Log
import com.example.smarty.core.domain.model.ChatMessage
import com.example.smarty.core.domain.model.ChatRole
import com.example.smarty.data.remote.AIService

/**
 * =============================================================================
 * CONVERSATION SUMMARIZER
 * =============================================================================
 *
 * AI-powered conversation summarization for context persistence.
 * Generates concise summaries of chat sessions for use in future conversations.
 *
 * PRIVACY RULES:
 * - Never include raw private note content in summaries
 * - Only store abstract descriptions of actions and topics
 * - Summaries should be safe to include in any context
 *
 * TRIGGERS for summary generation:
 * - When user starts a NEW session (summarize previous)
 * - When session has been inactive for 30+ minutes
 * - When session reaches 15+ messages
 *
 * =============================================================================
 */
object ConversationSummarizer {
    private const val TAG = "ConversationSummarizer"

    // Minimum messages required before generating a summary
    private const val MIN_MESSAGES_FOR_SUMMARY = 3

    // Maximum messages to include in summary context
    private const val MAX_MESSAGES_FOR_SUMMARY = 20

    // Maximum length of each message in summary context
    private const val MAX_MESSAGE_LENGTH = 200

    /**
     * System prompt for conversation summarization.
     * Designed to produce concise, privacy-safe summaries.
     */
    private val SUMMARY_SYSTEM_PROMPT =
        """
        You are a conversation summarizer. Your job is to create a brief, useful summary of a conversation.

        RULES:
        1. Summarize in 2-3 sentences maximum
        2. Focus on:
           - Main topics discussed
           - Any actions taken (notes created, todos added, searches performed, etc.)
           - Key user preferences or requests revealed
        3. Be concise and factual
        4. Do NOT include:
           - Specific private information (names, dates, specific content)
           - Raw note content or titles
           - Personal identifiable information
        5. Use abstract descriptions like "discussed work project" not "discussed Project Alpha with John"

        OUTPUT FORMAT:
        Just the summary text, nothing else. No labels, no quotes, no markdown.

        EXAMPLES:

        Good: "User asked about work notes and created a new todo list. Discussed organizing tasks by priority. User prefers concise responses."

        Bad: "User created a note titled 'Meeting with John about Project Alpha' and added todos for the Smith account."

        Good: "Searched for recipe notes and archived old ones. User expressed interest in meal planning features."

        Bad: "User searched for 'grandma's cookies recipe' and archived the note about last week's dinner party."
        """.trimIndent()

    /**
     * Generate a summary for a conversation.
     *
     * @param messages List of chat messages to summarize
     * @param aiService AI service for generating the summary
     * @return Generated summary, or null if generation fails or not enough messages
     */
    suspend fun generateSummary(
        messages: List<ChatMessage>,
        aiService: AIService,
    ): String? {
        // Don't summarize very short conversations
        if (messages.size < MIN_MESSAGES_FOR_SUMMARY) {
            Log.d(TAG, "Too few messages (${messages.size}) to summarize")
            return null
        }

        try {
            Log.d(TAG, "Generating summary for ${messages.size} messages")

            // Build conversation text for summarization
            val conversationText = buildConversationText(messages)

            // Generate summary via AI
            val summary =
                aiService.simpleChat(
                    systemPrompt = SUMMARY_SYSTEM_PROMPT,
                    userPrompt = "Summarize this conversation:\n\n$conversationText",
                )

            // Clean up the response
            val cleanSummary = cleanSummary(summary)

            if (cleanSummary.isBlank()) {
                Log.w(TAG, "Generated empty summary")
                return null
            }

            Log.d(TAG, "Generated summary: ${cleanSummary.take(100)}...")
            return cleanSummary
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate summary", e)
            return null
        }
    }

    /**
     * Build conversation text for summarization.
     * Limits message count and length for efficiency.
     */
    private fun buildConversationText(messages: List<ChatMessage>): String =
        messages
            .takeLast(MAX_MESSAGES_FOR_SUMMARY)
            .joinToString("\n") { message ->
                val role =
                    when (message.role) {
                        ChatRole.USER -> "User"
                        ChatRole.SMARTY -> "Smarty"
                        ChatRole.SYSTEM -> "System"
                    }
                val content = message.content.take(MAX_MESSAGE_LENGTH)
                val truncated = if (message.content.length > MAX_MESSAGE_LENGTH) "..." else ""
                "$role: $content$truncated"
            }

    /**
     * Clean up the generated summary.
     * Removes common unwanted prefixes/suffixes from AI responses.
     */
    private fun cleanSummary(summary: String): String {
        var clean = summary.trim()

        // Remove common prefixes
        val prefixes =
            listOf(
                "Summary:",
                "summary:",
                "Here is the summary:",
                "Here's the summary:",
                "The conversation summary:",
                "**Summary:**",
                "**Summary**",
            )
        for (prefix in prefixes) {
            if (clean.startsWith(prefix)) {
                clean = clean.removePrefix(prefix).trim()
            }
        }

        // Remove markdown formatting
        clean =
            clean
                .replace("**", "")
                .replace("*", "")
                .replace("`", "")

        // Remove quotes if the entire text is quoted
        if (clean.startsWith("\"") && clean.endsWith("\"")) {
            clean = clean.drop(1).dropLast(1)
        }

        // Limit length
        if (clean.length > 500) {
            clean = clean.take(497) + "..."
        }

        return clean.trim()
    }

    /**
     * Check if a session should have its summary updated.
     *
     * @param messageCount Current message count
     * @param lastSummaryAt When the summary was last generated (null if never)
     * @param lastActivityAt When the session was last active
     * @return true if summary should be regenerated
     */
    fun shouldUpdateSummary(
        messageCount: Int,
        lastSummaryAt: Long?,
        lastActivityAt: Long,
    ): Boolean {
        // Never summarized and has enough messages
        if (lastSummaryAt == null && messageCount >= MIN_MESSAGES_FOR_SUMMARY) {
            return true
        }

        // Summarized before, check if significantly more messages
        if (lastSummaryAt != null) {
            val timeSinceSummary = System.currentTimeMillis() - lastSummaryAt

            // Re-summarize if 30+ minutes have passed AND new activity
            if (timeSinceSummary > 30 * 60 * 1000 && lastActivityAt > lastSummaryAt) {
                return true
            }

            // Re-summarize if many new messages (15+ since last summary)
            // This is a heuristic - in practice, we'd track message count at summary time
            if (messageCount >= 15 && timeSinceSummary > 5 * 60 * 1000) {
                return true
            }
        }

        return false
    }

    /**
     * Generate a quick title for a conversation based on first few messages.
     * Useful for naming new sessions.
     *
     * @param messages First few messages of the conversation
     * @param aiService AI service for generating the title
     * @return Generated title, or default if generation fails
     */
    suspend fun generateTitle(
        messages: List<ChatMessage>,
        aiService: AIService,
    ): String {
        if (messages.isEmpty()) {
            return "New Chat"
        }

        try {
            val firstUserMessage =
                messages
                    .filter { it.role == ChatRole.USER }
                    .firstOrNull()
                    ?.content
                    ?.take(100)
                    ?: return "New Chat"

            val titlePrompt =
                """
                Generate a short title (2-5 words) for a conversation that starts with:
                "$firstUserMessage"

                Rules:
                - Maximum 5 words
                - No quotes or punctuation
                - Capture the main topic
                - Be specific but not private

                Just output the title, nothing else.
                """.trimIndent()

            val title =
                aiService.simpleChat(
                    systemPrompt = "You are a title generator. Output only the title, nothing else.",
                    userPrompt = titlePrompt,
                )

            val cleanTitle =
                title
                    .trim()
                    .replace("\"", "")
                    .replace("'", "")
                    .take(50)

            return if (cleanTitle.isNotBlank()) cleanTitle else "New Chat"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate title", e)
            return "New Chat"
        }
    }
}
