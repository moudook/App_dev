package com.example.smarty.server.agent

import com.example.smarty.server.llm.LlmMessage
import com.example.smarty.server.llm.LlmProvider
import org.slf4j.LoggerFactory

/**
 * Service to summarize conversation history to manage context window.
 */
class ConversationSummarizer(
    private val llmProvider: LlmProvider,
) {
    private val logger = LoggerFactory.getLogger(ConversationSummarizer::class.java)

    /**
     * Summarizes the provided messages into a concise string.
     */
    suspend fun summarize(messages: List<LlmMessage>): String {
        if (messages.isEmpty()) return ""

        logger.info("Summarizing ${messages.size} messages")

        val conversationText =
            messages.joinToString("\n") { message ->
                "${message.role.name}: ${message.content}"
            }

        val prompt =
            """
            Summarize the following conversation concisely.
            Focus on facts, user preferences, and key decisions.
            Keep the summary under 300 words.

            CONVERSATION:
            $conversationText

            SUMMARY:
            """.trimIndent()

        val systemMessage =
            LlmMessage(
                role = LlmMessage.Role.SYSTEM,
                content = prompt,
            )

        return try {
            // Use the non-streaming generate method for background summarization
            val response = llmProvider.generate(listOf(systemMessage))
            val result = response.content?.trim() ?: ""

            if (result.isEmpty()) {
                logger.warn("Received empty summary from LLM")
                "Conversation continues."
            } else {
                logger.info("Summary generated successfully (${result.length} chars)")
                result
            }
        } catch (e: Exception) {
            logger.error("Failed to summarize conversation", e)
            "A conversation occurred previously."
        }
    }
}
