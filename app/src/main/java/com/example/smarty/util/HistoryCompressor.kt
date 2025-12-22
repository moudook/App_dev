package com.example.smarty.util

import com.example.smarty.data.model.ChatMessage
import com.example.smarty.data.model.ChatRole

/**
 * Conversation history compression utility.
 *
 * Based on research recommendations for token optimization:
 * - Triggers compression when: >20 messages OR >4000 tokens
 * - Uses extractive summarization with fact extraction
 * - Preserves key entities, intents, and decisions in structured format
 *
 * Strategy:
 * - Keep last N exchanges (user+assistant pairs) verbatim
 * - Extract facts from older exchanges into structured memory
 * - Summarize remaining context
 *
 * Expected impact: 50-70% token reduction for long conversations
 */
object HistoryCompressor {

    private const val DEFAULT_RECENT_EXCHANGES = 3  // Keep 3 most recent exchanges verbatim
    private const val MAX_SUMMARY_CHARS = 500       // Max chars for summary section
    private const val MAX_MESSAGE_EXCERPT = 100     // Max chars per message in summary

    // Compression trigger thresholds (from research: compress when exceeded)
    private const val MESSAGE_COUNT_THRESHOLD = 20  // Compress when > 20 messages
    private const val TOKEN_COUNT_THRESHOLD = 4000  // Compress when > 4000 estimated tokens

    // Pre-compiled patterns for extracting key information
    private val ENTITY_PATTERNS = listOf(
        Regex("\\b[A-Z][a-z]+ [A-Z][a-z]+\\b"),           // Names (e.g., "John Smith")
        Regex("\\b\\d{1,2}[:/]\\d{2}\\s*(?:AM|PM|am|pm)?\\b"),  // Times
        Regex("\\b(?:today|tomorrow|yesterday|monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(?:create|delete|update|search|find|remind|schedule)\\b", RegexOption.IGNORE_CASE)  // Intent verbs
    )

    /**
     * Check if compression should be triggered based on thresholds.
     *
     * Research recommendation: Compress when >20 messages OR >4000 tokens
     */
    fun shouldCompress(messages: List<ChatMessage>): Boolean {
        if (messages.size > MESSAGE_COUNT_THRESHOLD) return true
        if (estimateTokens(messages) > TOKEN_COUNT_THRESHOLD) return true
        return false
    }

    /**
     * Compress conversation history for optimal token usage.
     *
     * @param messages Full conversation history
     * @param recentExchanges Number of recent exchanges to keep verbatim (default: 3)
     * @param maxSummaryChars Maximum characters for summary (default: 500)
     * @param forceCompress If true, compress even if below thresholds (default: false)
     * @return Compressed history with structured facts + summary + recent messages
     */
    fun compress(
        messages: List<ChatMessage>,
        recentExchanges: Int = DEFAULT_RECENT_EXCHANGES,
        maxSummaryChars: Int = MAX_SUMMARY_CHARS,
        forceCompress: Boolean = false
    ): List<ChatMessage> {
        if (messages.isEmpty()) return messages

        // Calculate threshold: 2 messages per exchange (user + assistant)
        val recentMessageCount = recentExchanges * 2

        // If short enough, return as-is
        if (messages.size <= recentMessageCount) return messages

        // Check if compression should be triggered (unless forced)
        if (!forceCompress && !shouldCompress(messages)) return messages

        // Split into older and recent
        val recent = messages.takeLast(recentMessageCount)
        val older = messages.dropLast(recentMessageCount)

        // Extract structured facts from older messages
        val facts = extractFacts(older)

        // Summarize older messages
        val summary = summarize(older, maxSummaryChars)

        // Build context message with structured facts + summary
        val contextContent = buildString {
            if (facts.isNotEmpty()) {
                appendLine("[FACTS FROM CONVERSATION:]")
                facts.forEach { fact ->
                    appendLine("- ${fact.category}: ${fact.value}")
                }
                appendLine()
            }
            appendLine("[CONVERSATION SUMMARY: $summary]")
        }

        val contextMessage = ChatMessage(
            role = ChatRole.SYSTEM,
            content = contextContent.trim()
        )

        return listOf(contextMessage) + recent
    }

    /**
     * Structured fact extracted from conversation.
     */
    data class ExtractedFact(
        val category: String,  // e.g., "user_preference", "mentioned_person", "task_outcome"
        val value: String,
        val confidence: Float = 1.0f
    )

    /**
     * Extract structured facts from messages for long-term memory.
     * Based on research: facts are more token-efficient than raw history.
     */
    private fun extractFacts(messages: List<ChatMessage>): List<ExtractedFact> {
        val facts = mutableListOf<ExtractedFact>()

        messages.forEach { message ->
            val content = message.content

            // Extract mentioned people/names
            ENTITY_PATTERNS[0].findAll(content).forEach { match ->
                facts.add(ExtractedFact("mentioned_person", match.value))
            }

            // Extract times/dates mentioned
            ENTITY_PATTERNS[1].findAll(content).forEach { match ->
                facts.add(ExtractedFact("mentioned_time", match.value))
            }
            ENTITY_PATTERNS[2].findAll(content).forEach { match ->
                facts.add(ExtractedFact("mentioned_day", match.value))
            }

            // Extract task outcomes from assistant actions
            if (message.role == ChatRole.ASSISTANT && message.executedActions.isNotEmpty()) {
                message.executedActions.forEach { action ->
                    val outcome = if (action.success) "completed" else "failed"
                    facts.add(ExtractedFact("task_outcome", "${action.action}: $outcome"))
                }
            }

            // Extract user preferences (simple heuristics)
            if (message.role == ChatRole.USER) {
                if (content.contains("prefer", ignoreCase = true) ||
                    content.contains("like to", ignoreCase = true) ||
                    content.contains("always", ignoreCase = true)) {
                    val excerpt = content.take(80)
                    facts.add(ExtractedFact("user_preference", excerpt, 0.7f))
                }
            }
        }

        // Deduplicate and limit to most important facts
        return facts
            .distinctBy { "${it.category}:${it.value}" }
            .sortedByDescending { it.confidence }
            .take(10)  // Keep top 10 facts to limit tokens
    }

    /**
     * Summarize a list of messages using extractive summarization.
     *
     * Extracts:
     * - Key entities (names, dates, times)
     * - User intents (create, search, remind, etc.)
     * - Important decisions/outcomes
     */
    private fun summarize(messages: List<ChatMessage>, maxChars: Int): String {
        if (messages.isEmpty()) return ""

        val summaryParts = mutableListOf<String>()

        // Group into exchanges (user + assistant pairs)
        val exchanges = messages.chunked(2)

        exchanges.forEach { exchange ->
            val userMsg = exchange.find { it.role == ChatRole.USER }
            val assistantMsg = exchange.find { it.role == ChatRole.ASSISTANT }

            // Extract user intent
            userMsg?.let {
                val excerpt = extractKeyPoints(it.content)
                if (excerpt.isNotBlank()) {
                    summaryParts.add("User: $excerpt")
                }
            }

            // Extract assistant action/response
            assistantMsg?.let {
                // Prioritize executed actions if any
                if (it.executedActions.isNotEmpty()) {
                    val actions = it.executedActions.joinToString(", ") {
                        action -> "${action.action}:${if (action.success) "ok" else "fail"}"
                    }
                    summaryParts.add("Actions: $actions")
                } else {
                    val excerpt = extractKeyPoints(it.content)
                    if (excerpt.isNotBlank()) {
                        summaryParts.add("Cogni: $excerpt")
                    }
                }
            }
        }

        // Join and truncate to max chars
        return summaryParts.joinToString("; ").take(maxChars)
    }

    /**
     * Extract key points from a message using extractive approach.
     *
     * Prioritizes:
     * 1. First sentence (usually contains main intent)
     * 2. Detected entities (names, times, dates)
     * 3. Intent verbs (create, search, remind)
     */
    private fun extractKeyPoints(text: String): String {
        if (text.isBlank()) return ""

        // Get first sentence
        val firstSentence = text.split(Regex("[.!?]"))
            .firstOrNull()
            ?.trim()
            ?.take(MAX_MESSAGE_EXCERPT)
            ?: ""

        // Find entities in the full text
        val entities = ENTITY_PATTERNS.flatMap { pattern ->
            pattern.findAll(text).map { it.value }
        }.distinct().take(3)

        // Combine: first sentence + any additional entities not in it
        val missingEntities = entities.filter { !firstSentence.contains(it, ignoreCase = true) }

        return if (missingEntities.isNotEmpty()) {
            "$firstSentence [${missingEntities.joinToString(", ")}]"
        } else {
            firstSentence
        }
    }

    /**
     * Estimate token count for a list of messages.
     * Uses rough approximation: ~4 characters per token for English.
     *
     * @param messages Messages to estimate
     * @return Approximate token count
     */
    fun estimateTokens(messages: List<ChatMessage>): Int {
        val totalChars = messages.sumOf { it.content.length }
        return (totalChars / 4.0).toInt()
    }

    /**
     * Calculate compression ratio achieved.
     *
     * @param original Original messages
     * @param compressed Compressed messages
     * @return Compression ratio (e.g., 0.3 means 70% reduction)
     */
    fun compressionRatio(original: List<ChatMessage>, compressed: List<ChatMessage>): Float {
        val originalTokens = estimateTokens(original)
        val compressedTokens = estimateTokens(compressed)
        return if (originalTokens > 0) {
            compressedTokens.toFloat() / originalTokens.toFloat()
        } else {
            1f
        }
    }
}
