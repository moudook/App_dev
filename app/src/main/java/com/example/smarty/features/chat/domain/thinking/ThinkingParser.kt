package com.example.smarty.features.chat.domain.thinking

data class ParsedResponse(
    val thinking: String?,
    val answer: String,
)

object ThinkingParser {
    fun parse(content: String): ParsedResponse = ParsedResponse(null, content)

    fun hasThinking(content: String): Boolean = false

    fun extractThinking(content: String): String? = null

    fun extractAnswer(content: String): String = content
}
