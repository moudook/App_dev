package com.example.smarty.features.chat.domain.thinking

data class ParsedResponse(
    val thinking: String?,
    val answer: String,
)

object ThinkingParser {
    private val thinkTagPattern = Regex("""\[think\](.*?)\[/think\]""", RegexOption.DOT_MATCHES_ALL)
    private val thinkHtmlPattern = Regex("""<think>(.*?)</think>""", RegexOption.DOT_MATCHES_ALL)

    fun parse(content: String): ParsedResponse {
        val match = thinkTagPattern.find(content) ?: thinkHtmlPattern.find(content)
        return if (match != null) {
            val thinking = match.groupValues[1].trim()
            val answer = content.replace(thinkTagPattern, "").replace(thinkHtmlPattern, "").trim()
            ParsedResponse(thinking = thinking.ifEmpty { null }, answer = answer)
        } else {
            ParsedResponse(null, content)
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
}
