package com.example.smarty.features.chat.domain.thinking

data class ParsedResponse(
    val thinking: String?,
    val answer: String,
)

object ThinkingParser {
    private val thinkTagPattern = Regex("""\[think\](.*?)\[/think\]""", RegexOption.DOT_MATCHES_ALL)
    private val thinkHtmlPattern = Regex("""<think>(.*?)</think>""", RegexOption.DOT_MATCHES_ALL)

    // Heuristic patterns for reasoning content that leaked into text deltas.
    // The OpenCode daemon sends reasoning as field:"text" deltas (not field:"reasoning"),
    // so the model's internal monologue appears at the start of the response.
    private val reasoningStartPatterns = listOf(
        Regex("""^The user (is|was|has|said|wants|needs|is asking|is looking|just|might)"""),
        Regex("""^This (is|was|looks|seems|appears|might be)"""),
        Regex("""^They (are|want|need|said|might|could)"""),
        Regex("""^(I|We) (need|should|must|can|could|will) (to |check |verify |look|search|find|create|update|delete|send|set|get|fetch|process|handle)"""),
        Regex("""^(Let me|I'll|I will|I should|I need to|Let's)"""),
        Regex("""^(The|A|An) (user|person|someone|request|query|message)"""),
    )

    // Patterns that signal the start of the actual response (greeting, direct answer)
    private val responseStartPatterns = listOf(
        Regex("""^(Hey|Hi|Hello|Sure|Of course|Absolutely|Certainly|Yeah|Yes|No|Well|So|Actually|Right|Okay|Ok|Hmm|Ah)"""),
        Regex("""^[A-Z][a-z]+ (doing|going|is|was|has|can|will|looks|sounds|feels|seems)"""),
    )

    fun parse(content: String): ParsedResponse {
        val match = thinkTagPattern.find(content) ?: thinkHtmlPattern.find(content)
        return if (match != null) {
            val thinking = match.groupValues[1].trim()
            val answer = content.replace(thinkTagPattern, "").replace(thinkHtmlPattern, "").trim()
            ParsedResponse(thinking = thinking.ifEmpty { null }, answer = answer)
        } else {
            // No think tags — try to extract reasoning from text heuristics
            val extracted = extractReasoningFromText(content)
            if (extracted != null) {
                extracted
            } else {
                ParsedResponse(null, content)
            }
        }
    }

    fun hasThinking(content: String): Boolean =
        thinkTagPattern.containsMatchIn(content) || thinkHtmlPattern.containsMatchIn(content)

    fun extractThinking(content: String): String? {
        val match = thinkTagPattern.find(content) ?: thinkHtmlPattern.find(content)
        return match?.groupValues?.get(1)?.trim()?.ifEmpty { null }
    }

    fun extractAnswer(content: String): String =
        content.replace(thinkTagPattern, "").replace(thinkHtmlPattern, "").trim()

    /**
     * Try to extract reasoning content that leaked into the text response.
     * The daemon sends reasoning as field:"text" deltas, so the model's internal
     * monologue appears at the start of the response. This method detects that
     * pattern and splits reasoning from the actual answer.
     */
    fun extractReasoningFromText(content: String): ParsedResponse? {
        if (content.length < 20) return null

        val hasReasoningStart = reasoningStartPatterns.any { it.containsMatchIn(content) }
        if (!hasReasoningStart) return null

        // Find where the actual response starts by looking for greeting/answer patterns
        for (pattern in responseStartPatterns) {
            val match = pattern.find(content)
            if (match != null && match.range.first > 10) {
                val reasoning = content.substring(0, match.range.first).trim()
                val answer = content.substring(match.range.first).trim()
                if (reasoning.length > 15 && answer.isNotEmpty()) {
                    return ParsedResponse(thinking = reasoning, answer = answer)
                }
            }
        }

        return null
    }
}
