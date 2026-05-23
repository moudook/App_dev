package com.example.smarty.features.chat.domain.thinking

data class ParsedResponse(
    val thinking: String?,
    val answer: String,
)

object ThinkingParser {
    private val thinkRegex = Regex("<think>(.*?)</think>", RegexOption.DOT_MATCHES_ALL)
    private val finalRegex = Regex("<final>(.*?)</final>", RegexOption.DOT_MATCHES_ALL)

    fun parse(content: String): ParsedResponse {
        // First try to find <final> tags (new format)
        val finalMatch = finalRegex.find(content)
        val thinkMatch = thinkRegex.find(content)

        return when {
            // Both thinking and final tags present
            thinkMatch != null && finalMatch != null -> {
                val thinking = thinkMatch.groupValues[1].trim()
                val answer = finalMatch.groupValues[1].trim()
                ParsedResponse(thinking, answer)
            }
            // Only thinking tags
            thinkMatch != null -> {
                val thinking = thinkMatch.groupValues[1].trim()
                val answer = content.replace(thinkMatch.value, "").trim()
                ParsedResponse(thinking, answer)
            }
            // Only final tags
            finalMatch != null -> {
                val answer = finalMatch.groupValues[1].trim()
                ParsedResponse(null, answer)
            }
            // No tags - entire content is answer
            else -> ParsedResponse(null, content)
        }
    }

    fun hasThinking(content: String): Boolean {
        return thinkRegex.containsMatchIn(content)
    }

    fun extractThinking(content: String): String? {
        return thinkRegex.find(content)?.groupValues?.get(1)?.trim()
    }

    fun extractAnswer(content: String): String {
        // Remove thinking tags first
        var answer = thinkRegex.replace(content, "")
        // Then extract final tag content if present
        val finalMatch = finalRegex.find(answer)
        return if (finalMatch != null) {
            finalMatch.groupValues[1].trim()
        } else {
            answer.trim()
        }
    }
}
