package com.example.smarty.features.chat.domain.thinking

data class ParsedResponse(
    val thinking: String?,
    val answer: String,
)

object ThinkingParser {

    fun parse(content: String): ParsedResponse {
        return ParsedResponse(null, content)
    }

    fun hasThinking(content: String): Boolean {
        return false
    }

    fun extractThinking(content: String): String? {
        return null
    }

    fun extractAnswer(content: String): String {
        return content
    }
}
