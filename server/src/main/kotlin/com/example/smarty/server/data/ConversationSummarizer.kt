package com.example.smarty.server.data

import com.example.smarty.server.llm.LlmMessage
import com.example.smarty.server.llm.LlmProvider
import org.slf4j.LoggerFactory

/**
 * =============================================================================
 * CONVERSATION SUMMARIZER (Server Side)
 * =============================================================================
 *
 * AI-powered conversation summarization for context persistence.
 * Generates concise summaries of chat sessions for use in future conversations.
 *
 * PRIVACY RULES:
 * - Never include raw private note content in summaries
 * - Only store abstract descriptions of actions and topics
 * - Summaries should be safe to include in any context
 */
class ConversationSummarizer(private val llmProvider: LlmProvider) {

    private val logger = LoggerFactory.getLogger(ConversationSummarizer::class.java)

companion object {
        private const val MIN_MESSAGES_FOR_SUMMARY = 3
        private const val MAX_MESSAGES_FOR_SUMMARY = 20
        private const val MAX_MESSAGE_LENGTH = 200

        private val SUMMARY_SYSTEM_PROMPT = """
<identity>
You are Friday's Conversation Summarizer. Create brief, useful summaries for future context.
</identity>

<task>
Summarize the conversation in 2-3 sentences max. Focus on what matters for future interactions.
</task>

<content_focus>
- Main topics discussed
- Actions taken (notes created, todos added, searches performed)
- Key user preferences revealed
</content_focus>

<privacy_rules>
- NO specific private info (passwords, financial details)
- Abstract sensitive names unless critical
- Use "discussed work project" not "discussed Project Alpha details"
</privacy_rules>

<output_format>
Return ONLY the summary text. No labels, no markdown, no quotes.
</output_format>

<example>
Input: Long conversation about setting up a React Native project
Output: User set up React Native project with Expo. Created notes about environment setup. Prefers TypeScript over JavaScript.
</example>
        """.trimIndent()
    }

    /**
     * Generate a summary for a conversation.
     *
     * @param messages List of chat messages to summarize
     * @return Generated summary, or null if generation fails or not enough messages
     */
    suspend fun generateSummary(messages: List<LlmMessage>): String? {
        // Don't summarize very short conversations
        if (messages.size < MIN_MESSAGES_FOR_SUMMARY) {
            logger.debug("Too few messages (${messages.size}) to summarize")
            return null
        }

        try {
            logger.debug("Generating summary for ${messages.size} messages")

            // Build conversation text for summarization
            val conversationText = buildConversationText(messages)

            // Generate summary via AI
            val promptMessages = listOf(
                LlmMessage(LlmMessage.Role.SYSTEM, SUMMARY_SYSTEM_PROMPT),
                LlmMessage(LlmMessage.Role.USER, "Summarize this conversation:\n\n$conversationText")
            )

            val response = llmProvider.generate(promptMessages)
            val summary = response.content ?: return null

            // Clean up the response
            val cleanSummary = cleanSummary(summary)

            if (cleanSummary.isBlank()) {
                logger.warn("Generated empty summary")
                return null
            }

            logger.debug("Generated summary: ${cleanSummary.take(100)}...")
            return cleanSummary

        } catch (e: Exception) {
            logger.error("Failed to generate summary", e)
            return null
        }
    }

    /**
     * Build conversation text for summarization.
     * Limits message count and length for efficiency.
     */
    private fun buildConversationText(messages: List<LlmMessage>): String {
        return messages
            .takeLast(MAX_MESSAGES_FOR_SUMMARY)
            .joinToString("\n") { message ->
                val role = message.role.name
                val content = message.content.take(MAX_MESSAGE_LENGTH)
                val truncated = if (message.content.length > MAX_MESSAGE_LENGTH) "..." else ""
                "$role: $content$truncated"
            }
    }

    /**
     * Clean up the generated summary.
     * Removes common unwanted prefixes/suffixes from AI responses.
     */
    private fun cleanSummary(summary: String): String {
        var clean = summary.trim()

        // Remove common prefixes
        val prefixes = listOf(
            "Summary:", "summary:",
            "Here is the summary:", "Here's the summary:",
            "The conversation summary:",
            "**Summary:**", "**Summary**"
        )
        for (prefix in prefixes) {
            if (clean.startsWith(prefix)) {
                clean = clean.removePrefix(prefix).trim()
            }
        }

        // Remove markdown formatting
        clean = clean
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
     * Generate a quick title for a conversation based on first few messages.
     * Useful for naming new sessions.
     *
     * @param messages First few messages of the conversation
     * @return Generated title, or default if generation fails
     */
    suspend fun generateTitle(messages: List<LlmMessage>): String {
        if (messages.isEmpty()) {
            return "New Chat"
        }

        try {
            val firstUserMessage = messages
                .filter { it.role == LlmMessage.Role.USER }
                .firstOrNull()
                ?.content
                ?.take(100)
                ?: return "New Chat"

val titlePrompt = """
<task>
Generate a 2-5 word title for a conversation starting with: "$firstUserMessage"
</task>

<rules>
- Maximum 5 words
- No quotes or punctuation
- Capture the main topic
- Be specific but not private
- Output ONLY the title
</rules>
            """.trimIndent()

            val promptMessages = listOf(
                LlmMessage(LlmMessage.Role.SYSTEM, "You are a title generator. Output only the title."),
                LlmMessage(LlmMessage.Role.USER, titlePrompt)
            )

            val response = llmProvider.generate(promptMessages)
            val title = response.content ?: return "New Chat"

            val cleanTitle = title
                .trim()
                .replace("\"", "")
                .replace("'", "")
                .take(50)

            return if (cleanTitle.isNotBlank()) cleanTitle else "New Chat"

        } catch (e: Exception) {
            logger.error("Failed to generate title", e)
            return "New Chat"
        }
    }
}
