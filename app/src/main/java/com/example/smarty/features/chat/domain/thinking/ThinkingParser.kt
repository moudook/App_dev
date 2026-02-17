package com.example.smarty.features.chat.domain.thinking

data class ParsedResponse(
    val thinking: String?,
    val answer: String
)

object ThinkingParser {
    
    private val thinkRegex = Regex("<think>(.*?)</think>", RegexOption.DOT_MATCHES_ALL)
    
    fun parse(content: String): ParsedResponse {
        val match = thinkRegex.find(content)
        
        return if (match != null) {
            val thinking = match.groupValues[1].trim()
            val answer = content.replace(match.value, "").trim()
            ParsedResponse(thinking, answer)
        } else {
            ParsedResponse(null, content)
        }
    }
    
    fun hasThinking(content: String): Boolean {
        return content.contains("<think>") && content.contains("</think>")
    }
    
    fun extractThinking(content: String): String? {
        val match = thinkRegex.find(content)
        return match?.groupValues?.get(1)?.trim()
    }
    
    fun extractAnswer(content: String): String {
        return content.replace(thinkRegex, "").trim()
    }
}
