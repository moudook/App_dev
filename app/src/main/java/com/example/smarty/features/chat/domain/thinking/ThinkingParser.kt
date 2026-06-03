package com.example.smarty.features.chat.domain.thinking

data class ParsedResponse(
    val thinking: String?,
    val answer: String,
)

object ThinkingParser {
    private val thinkTagPattern = Regex("""\[think](.*?)(?:\[/think]|$)""", RegexOption.DOT_MATCHES_ALL)
    private val thinkHtmlPattern = Regex("""<think>(.*?)(?:</think>|$)""", RegexOption.DOT_MATCHES_ALL)

    // Simple one-entry length cache to avoid re-parsing the same accumulated string
    // during streaming (pushBlocks() is called for every delta token).
    @Volatile private var cachedInput = ""
    @Volatile private var cachedResult: ParsedResponse? = null

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

    /**
     * Single-pass parse. Results are length-cached so repeated calls with the same
     * growing string (streaming) pay near-zero cost.
     */
    fun parse(content: String): ParsedResponse {
        // Cache hit: avoid double regex on unchanged string
        if (content === cachedInput || content == cachedInput) {
            cachedResult?.let { return it }
        }

        val result = doParse(content)
        cachedInput = content
        cachedResult = result
        return result
    }

    private fun doParse(content: String): ParsedResponse {
        // Single regex pass — find first think block
        val match = thinkTagPattern.find(content) ?: thinkHtmlPattern.find(content)
        return if (match != null) {
            val thinking = match.groupValues[1].trim()
            // Build answer by removing ALL think blocks in a single replace pass
            val answer = content
                .replace(thinkTagPattern, "")
                .replace(thinkHtmlPattern, "")
                .trim()
            ParsedResponse(thinking = thinking.ifEmpty { null }, answer = answer)
        } else {
            extractReasoningFromText(content) ?: ParsedResponse(null, content)
        }
    }

    fun hasThinking(content: String): Boolean =
        thinkTagPattern.containsMatchIn(content) || thinkHtmlPattern.containsMatchIn(content)

    /** Prefer parse() to avoid double-pass — these are kept for one-off callers. */
    fun extractThinking(content: String): String? = parse(content).thinking

    fun extractAnswer(content: String): String = parse(content).answer

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
