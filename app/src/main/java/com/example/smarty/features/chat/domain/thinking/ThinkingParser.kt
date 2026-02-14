package com.example.smarty.features.chat.domain.thinking

/**
 * Parsed response containing thinking process and final answer.
 * Used for displaying reasoning from models like Falcon-H1R-7B.
 */
data class ParsedResponse(
    val thinking: String?,  // Content inside <think> tags (reasoning process)
    val answer: String      // Content after </think> tags (final answer)
)

/**
 * Utility for parsing thinking tags from AI model responses.
 * 
 * Reasoning models like Falcon-H1R-7B emit their thought process
 * within <think>...</think> tags before providing the final answer.
 * 
 * Example response:
 * ```
 * <think>
 * Let me solve this step by step:
 * 1. 2x + 5 = 13
 * 2. 2x = 13 - 5
 * 3. 2x = 8
 * 4. x = 4
 * </think>
 * 
 * The solution is x = 4.
 * ```
 */
object ThinkingParser {
    
    /**
     * Parse a response containing <think> tags.
     * 
     * @param content The full response from the AI model
     * @return ParsedResponse with separated thinking and answer
     */
    fun parse(content: String): ParsedResponse {
        // Match <think>...</think> tags (including newlines)
        val thinkRegex = Regex("<think>(.*?)</think>", RegexOption.DOT_MATCHES_ALL)
        val match = thinkRegex.find(content)
        
        return if (match != null) {
            // Extract thinking content (inside tags)
            val thinking = match.groupValues[1].trim()
            
            // Extract answer (everything after the closing tag)
            val answer = content.replace(match.value, "").trim()
            
            ParsedResponse(thinking, answer)
        } else {
            // No thinking tags found - entire content is the answer
            ParsedResponse(null, content)
        }
    }
    
    /**
     * Check if a response contains thinking tags.
     * 
     * @param content The response to check
     * @return true if <think> tags are present
     */
    fun hasThinking(content: String): Boolean {
        return content.contains("<think>") && content.contains("</think>")
    }
    
    /**
     * Extract only the thinking content without tags.
     * 
     * @param content The response containing <think> tags
     * @return The thinking content, or null if no tags found
     */
    fun extractThinking(content: String): String? {
        val thinkRegex = Regex("<think>(.*?)</think>", RegexOption.DOT_MATCHES_ALL)
        val match = thinkRegex.find(content)
        return match?.groupValues?.get(1)?.trim()
    }
    
    /**
     * Extract only the answer content (everything outside <think> tags).
     * 
     * @param content The response containing <think> tags
     * @return The answer content
     */
    fun extractAnswer(content: String): String {
        val thinkRegex = Regex("<think>(.*?)</think>", RegexOption.DOT_MATCHES_ALL)
        return content.replace(thinkRegex, "").trim()
    }
}
